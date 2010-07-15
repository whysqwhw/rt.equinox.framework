/*******************************************************************************
 * Copyright (c) 2003, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.osgi.internal.serviceregistry;

import org.eclipse.osgi.framework.debug.Debug;
import org.eclipse.osgi.framework.internal.core.*;
import org.osgi.framework.*;
import org.osgi.framework.Constants;
import org.osgi.framework.hooks.service.ListenerHook;

/**
 * Service Listener delegate.
 */
class FilteredServiceListener implements ServiceListener, ListenerHook.ListenerInfo {
	/** Filter for listener. */
	private final FilterImpl filter;
	/** Real listener. */
	private final ServiceListener listener;
	/** The bundle context */
	private final BundleContextImpl context;
	/** is this an AllServiceListener */
	private final boolean allservices;
	/** an objectClass required by the filter */
	private final String objectClass;
	/** indicates whether the listener has been removed */
	private volatile boolean removed;

	/**
	 * Constructor.
	 *
	 * @param context The bundle context of the bundle which added the specified service listener.
	 * @param filterstring The filter string specified when this service listener was added.
	 * @param listener The service listener object.
	 * @exception InvalidSyntaxException if the filter is invalid.
	 */
	FilteredServiceListener(final BundleContextImpl context, final ServiceListener listener, final String filterstring) throws InvalidSyntaxException {
		if (filterstring == null) {
			this.filter = null;
			this.objectClass = null;
		} else {
			FilterImpl filterImpl = FilterImpl.newInstance(filterstring);
			String clazz = filterImpl.getRequiredObjectClass();
			if (clazz == null) {
				this.objectClass = null;
				this.filter = filterImpl;
			} else {
				this.objectClass = clazz.intern(); /*intern the name for future identity comparison */
				this.filter = filterstring.equals(getObjectClassFilterString(this.objectClass)) ? null : filterImpl;
			}
		}
		this.removed = false;
		this.listener = listener;
		this.context = context;
		this.allservices = (listener instanceof AllServiceListener);
	}

	/**
	 * Receives notification that a service has had a lifecycle change.
	 * 
	 * @param event The <code>ServiceEvent</code> object.
	 */
	public void serviceChanged(ServiceEvent event) {
		ServiceReferenceImpl<?> reference = (ServiceReferenceImpl<?>) event.getServiceReference();

		// first check if we can short circuit the filter match if the required objectClass does not match the event
		objectClassCheck: if (objectClass != null) {
			String[] classes = reference.getClasses();
			int size = classes.length;
			for (int i = 0; i < size; i++) {
				if (classes[i] == objectClass) // objectClass strings have previously been interned for identity comparison 
					break objectClassCheck;
			}
			return; // no class in this event matches a required part of the filter; we do not need to deliver this event
		}
		// TODO could short circuit service.id filters as well since the id is constant for a registration.

		if (!ServiceRegistry.hasListenServicePermission(event, context))
			return;

		if (Debug.DEBUG_EVENTS) {
			String listenerName = this.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(this)); //$NON-NLS-1$
			Debug.println("filterServiceEvent(" + listenerName + ", \"" + getFilter() + "\", " + reference.getRegistration().getProperties() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		}

		event = filterMatch(event, reference);
		if (event == null) {
			return;
		}
		if (allservices || ServiceRegistry.isAssignableTo(context, reference)) {
			if (Debug.DEBUG_EVENTS) {
				String listenerName = listener.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(listener)); //$NON-NLS-1$
				Debug.println("dispatchFilteredServiceEvent(" + listenerName + ")"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (event.getType() == ServiceEvent.MODIFIED && (event instanceof ServicePolicyChangeEvent))
				((ServicePolicyChangeEvent) event).addListener(this);
			else
				listener.serviceChanged(event);
		}
	}

	/**
	 * Returns a service event that should be delivered to the listener based on the filter evaluation.
	 * This may result in a service event of type MODIFIED_ENDMATCH.
	 * 
	 * @param delivered The service event delivered by the framework.
	 * @return The event to be delivered or null if no event is to be delivered to the listener.
	 */
	private ServiceEvent filterMatch(ServiceEvent delivered, ServiceReferenceImpl<?> reference) {
		boolean modified = delivered.getType() == ServiceEvent.MODIFIED;
		ServiceEvent event;
		if (!modified || delivered instanceof ServicePolicyChangeEvent)
			event = delivered;
		else
			event = ((ModifiedServiceEvent) delivered).getModifiedEvent();
		Bundle providerBundle = reference.getRegistration().getBundle();
		boolean filterMatchCurrent = filter == null || filter.match(reference);
		boolean policyMatchCurrent = checkSharingPolicy(reference, providerBundle, reference.getClasses());
		if (!modified) {
			// Simple case; fire the event if we match.
			// check the filter and sharing policy
			return filterMatchCurrent && policyMatchCurrent ? event : null;
		}

		// this is for the modified case
		if (filterMatchCurrent && policyMatchCurrent)
			// always fire the modified event if both match current
			return event;

		// We get here if the service has been modified and it no longer is visible
		// to the listener either because the filter does not match or the policy does not match.
		// We need to do an extra check to see if this service was previously visible to the listener.
		// If so then an end match event is fired; otherwise no event is fired.
		ModifiedServiceEvent modifiedServiceEvent = (ModifiedServiceEvent) delivered;
		boolean filterMatchPrevious = modifiedServiceEvent.matchPreviousProperties(filter);
		boolean policyMatchPrevious = checkSharingPolicy(modifiedServiceEvent, providerBundle, reference.getClasses());

		// fire end match only if both the filter and policy previously matched;
		// otherwise do not fire any event because the service was not visible previously
		if (filterMatchPrevious && policyMatchPrevious)
			return modifiedServiceEvent.getModifiedEndMatchEvent();
		return null;
	}

	private boolean checkSharingPolicy(ServiceReference<?> reference, Bundle providerBundle, String[] classes) {
		ScopePolicy scopePolicy = context.getFramework().getCompositeSupport().getCompositePolicy();
		return scopePolicy.isVisible(context.getBundle(), providerBundle, reference, classes);
	}

	/**
	 * The string representation of this Filtered listener.
	 *
	 * @return The string representation of this listener.
	 */
	public String toString() {
		String filterString = getFilter();
		if (filterString == null) {
			filterString = ""; //$NON-NLS-1$
		}
		return listener.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(listener)) + filterString; //$NON-NLS-1$
	}

	/** 
	 * Return the bundle context for the ListenerHook.
	 * @return The context of the bundle which added the service listener.
	 * @see org.osgi.framework.hooks.service.ListenerHook.ListenerInfo#getBundleContext()
	 */
	public BundleContext getBundleContext() {
		return context;
	}

	/** 
	 * Return the filter string for the ListenerHook.
	 * @return The filter string with which the listener was added. This may
	 * be <code>null</code> if the listener was added without a filter.
	 * @see org.osgi.framework.hooks.service.ListenerHook.ListenerInfo#getFilter()
	 */
	public String getFilter() {
		if (filter != null) {
			return filter.toString();
		}
		return getObjectClassFilterString(objectClass);
	}

	/**
	 * Return the state of the listener for this addition and removal life
	 * cycle. Initially this method will return <code>false</code>
	 * indicating the listener has been added but has not been removed.
	 * After the listener has been removed, this method must always return
	 * <code>true</code>.
	 * 
	 * @return <code>false</code> if the listener has not been been removed,
	 *         <code>true</code> otherwise.
	 */
	public boolean isRemoved() {
		return removed;
	}

	/** 
	 * Mark the service listener registration as removed.
	 */
	void markRemoved() {
		removed = true;
	}

	/**
	 * Returns an objectClass filter string for the specified class name.
	 * @return A filter string for the specified class name or <code>null</code> if the 
	 * specified class name is <code>null</code>.
	 */
	private static String getObjectClassFilterString(String className) {
		if (className == null) {
			return null;
		}
		return "(" + Constants.OBJECTCLASS + "=" + className + ")"; //$NON-NLS-1$//$NON-NLS-2$ //$NON-NLS-3$
	}

	void fireSyntheticEvent(ServiceEvent event) {
		// no checks necessary, this event must be fired
		listener.serviceChanged(event);
	}
}
