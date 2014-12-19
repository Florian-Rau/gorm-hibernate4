/*
 * Copyright 2004-2006 Graeme Rocher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.orm.hibernate.support;

import grails.core.GrailsDomainClassProperty;
import grails.persistence.support.PersistenceContextInterceptor;
import grails.validation.DeferredBindingActions;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.grails.orm.hibernate.AbstractHibernateGormInstanceApi;
import org.grails.orm.hibernate.cfg.GrailsHibernateUtil;
import org.grails.core.lifecycle.ShutdownOperations;
import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.internal.SessionImpl;
import org.springframework.orm.hibernate4.SessionFactoryUtils;
import org.springframework.orm.hibernate4.SessionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Graeme Rocher
 * @since 0.4
 */
public class HibernatePersistenceContextInterceptor implements PersistenceContextInterceptor, SessionFactoryAwarePersistenceContextInterceptor {

    private static final Log LOG = LogFactory.getLog(HibernatePersistenceContextInterceptor.class);
    private SessionFactory sessionFactory;


    private static ThreadLocal<Map<String, Boolean>> participate = new ThreadLocal<Map<String, Boolean>>() {
        @Override
        protected Map<String, Boolean> initialValue() {
            return new HashMap<String, Boolean>();
        }
    };

    private static ThreadLocal<Map<String, Integer>> nestingCount = new ThreadLocal<Map<String, Integer>>() {
        @Override
        protected Map<String, Integer> initialValue() {
            return new HashMap<String, Integer>();
        }
    };


    private String dataSourceName;

    static {
        ShutdownOperations.addOperation(new Runnable() {
            public void run() {
                participate.remove();
                nestingCount.remove();
            }
        });
    }


    public HibernatePersistenceContextInterceptor() {
        this.dataSourceName = GrailsDomainClassProperty.DEFAULT_DATA_SOURCE;
    }

    /**
     * @param dataSourceName a name of dataSource
     */
    public HibernatePersistenceContextInterceptor(String dataSourceName) {
        this.dataSourceName = dataSourceName;
    }

    /* (non-Javadoc)
     * @see org.codehaus.groovy.grails.support.PersistenceContextInterceptor#destroy()
     */
    public void destroy() {
        DeferredBindingActions.clear();
        if (getSessionFactory() == null || decNestingCount() > 0 || getParticipate()) {
            return;
        }

        try {
            // single session mode
            SessionHolder holder = (SessionHolder)TransactionSynchronizationManager.unbindResource(getSessionFactory());
            LOG.debug("Closing single Hibernate session in GrailsDispatcherServlet");
            try {
                SessionFactoryUtils.closeSession(holder.getSession());
            }
            catch (RuntimeException ex) {
                LOG.error("Unexpected exception on closing Hibernate Session", ex);
            }
        }
        finally {
            AbstractHibernateGormInstanceApi.clearDisabledValidations();
        }
    }

    public void disconnect() {
        if (getSessionFactory() == null) return;
        try {
            getSession(false).disconnect();
        }
        catch (Exception e) {
            // no session ignore
        }
    }

    public void reconnect() {
        if (getSessionFactory() == null) return;
        Connection connection = ((SessionImpl) getSession()).connection();
        Session session = getSession();
        if(!session.isConnected()) {
            try {
                getSession().reconnect(connection);
            } catch (IllegalStateException e) {
                // cannot reconnect on different exception. ignore
                LOG.debug(e.getMessage(),e);
            }
        }
    }

    public void flush() {
        if (getSessionFactory() == null) return;
        getSession().flush();
    }

    public void clear() {
        if (getSessionFactory() == null) return;
        getSession().clear();
    }

    public void setReadOnly() {
        if (getSessionFactory() == null) return;        
        getSession().setFlushMode(FlushMode.MANUAL);
    }

    public void setReadWrite() {
        if (getSessionFactory() == null) return;        
        getSession().setFlushMode(FlushMode.AUTO);
    }

    public boolean isOpen() {
        if (getSessionFactory() == null) return false;
        try {
            return getSession(false).isOpen();
        }
        catch (Exception e) {
            return false;
        }
    }

    /* (non-Javadoc)
     * @see org.codehaus.groovy.grails.support.PersistenceContextInterceptor#init()
     */
    public void init() {
        if (incNestingCount() > 1) {
            return;
        }
        SessionFactory sf = getSessionFactory();
        if (sf == null) {
            return;
        }        
        if (TransactionSynchronizationManager.hasResource(sf)) {
            // Do not modify the Session: just set the participate flag.
            setParticipate(true);
        }
        else {
            setParticipate(false);
            LOG.debug("Opening single Hibernate session in HibernatePersistenceContextInterceptor");
            Session session = getSession();
            GrailsHibernateUtil.enableDynamicFilterEnablerIfPresent(sf, session);
            session.setFlushMode(FlushMode.AUTO);
            TransactionSynchronizationManager.bindResource(sf, new SessionHolder(session));
        }
    }

    private Session getSession() {
        return getSession(true);
    }

    private Session getSession(boolean allowCreate) {

        Object value = TransactionSynchronizationManager.getResource(getSessionFactory());
        if (value instanceof Session) {
            return (Session) value;
        }

        if (value instanceof SessionHolder) {
            SessionHolder sessionHolder = (SessionHolder) value;
            return sessionHolder.getSession();
        }

        if (allowCreate) {
            return getSessionFactory().openSession();
        }

        throw new IllegalStateException("No Hibernate Session bound to thread, and configuration does not allow creation of non-transactional one here");
    }

    /**
     * @return the sessionFactory
     */
    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    /**
     * @param sessionFactory the sessionFactory to set
     */
    public void setSessionFactory(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    private int incNestingCount() {
        Map<String, Integer> map = nestingCount.get();
        Integer current = map.get(dataSourceName);
        int value = (current != null) ? current + 1 : 1;
        map.put(dataSourceName, value);
        return value;
    }

    private int decNestingCount() {
        Map<String, Integer> map = nestingCount.get();
        Integer current = map.get(dataSourceName);
        int value = (current != null) ? current - 1 : 0;
        if (value < 0) {
            value = 0;
        }
        map.put(dataSourceName, value);
        return value;
    }

    private void setParticipate(boolean flag) {
        Map<String, Boolean> map = participate.get();
        map.put(dataSourceName, flag);
    }

    private boolean getParticipate() {
        Map<String, Boolean> map = participate.get();
        Boolean ret = map.get(dataSourceName);
        return (ret != null) ? ret : false;
    }
}
