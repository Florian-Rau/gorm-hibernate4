/*
 * Copyright 2013 the original author or authors.
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
package org.codehaus.groovy.grails.orm.hibernate

import grails.orm.HibernateCriteriaBuilder
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import org.codehaus.groovy.grails.commons.DomainClassArtefactHandler
import org.codehaus.groovy.grails.commons.GrailsApplication
import org.codehaus.groovy.grails.commons.GrailsDomainClass
import org.codehaus.groovy.grails.orm.hibernate.cfg.GrailsDomainBinder
import org.codehaus.groovy.grails.orm.hibernate.metaclass.ListPersistentMethod
import org.codehaus.groovy.grails.orm.hibernate.metaclass.MergePersistentMethod
import org.grails.datastore.gorm.finders.FinderMethod
import org.grails.datastore.mapping.query.api.Criteria as GrailsCriteria
import org.hibernate.LockMode
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.springframework.core.convert.ConversionService
import org.springframework.orm.hibernate4.SessionFactoryUtils
import org.springframework.orm.hibernate4.SessionHolder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * The implementation of the GORM static method contract for Hibernate
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@CompileStatic
class HibernateGormStaticApi<D> extends AbstractHibernateGormStaticApi<D> {
    protected static final EMPTY_ARRAY = [] as Object[]

    protected GrailsHibernateTemplate hibernateTemplate
    protected SessionFactory sessionFactory
    protected ConversionService conversionService
    protected Class identityType
    protected ListPersistentMethod listMethod
    protected MergePersistentMethod mergeMethod
    protected ClassLoader classLoader
    protected GrailsApplication grailsApplication
    protected boolean cacheQueriesByDefault = false
    protected GrailsDomainBinder grailsDomainBinder = new GrailsDomainBinder()

    HibernateGormStaticApi(Class<D> persistentClass, HibernateDatastore datastore, List<FinderMethod> finders,
                ClassLoader classLoader, PlatformTransactionManager transactionManager) {
        super(persistentClass, datastore, finders, transactionManager, new GrailsHibernateTemplate(datastore.sessionFactory))
        this.classLoader = classLoader
        sessionFactory = datastore.getSessionFactory()
        conversionService = datastore.mappingContext.conversionService

        identityType = persistentEntity.identity?.type

        def mappingContext = datastore.mappingContext
        grailsApplication = datastore.grailsApplication
        if (grailsApplication) {
            GrailsDomainClass domainClass = (GrailsDomainClass)grailsApplication.getArtefact(DomainClassArtefactHandler.TYPE, persistentClass.name)
            identityType = domainClass.identifier?.type

            mergeMethod = new MergePersistentMethod(sessionFactory, classLoader, grailsApplication, domainClass, datastore)
            listMethod = new ListPersistentMethod(grailsApplication, sessionFactory, classLoader, mappingContext.conversionService)
            hibernateTemplate = new GrailsHibernateTemplate(sessionFactory)
            hibernateTemplate.setCacheQueries(cacheQueriesByDefault)
        } else {
            hibernateTemplate = new GrailsHibernateTemplate(sessionFactory)
        }
    }


    @Override
    GrailsCriteria createCriteria() {
        def builder = new HibernateCriteriaBuilder(persistentClass, sessionFactory)
        builder.grailsApplication = grailsApplication
        builder.conversionService = conversionService
        builder
    }

    @Override
    D lock(Serializable id) {
        (D)hibernateTemplate.lock((Class)persistentClass, convertIdentifier(id), LockMode.PESSIMISTIC_WRITE)
    }

    @Override
    D merge(o) {
        (D)mergeMethod.invoke(o, "merge", [] as Object[])
    }


    @Override
    List<D> list(Map params) {
        (List<D>)listMethod.invoke(persistentClass, "list", [params] as Object[])
    }

    @Override
    List<D> list() {
        (List<D>)listMethod.invoke(persistentClass, "list", EMPTY_ARRAY)
    }

    @Override
    Integer executeUpdate(String query, Map params, Map args) {
        def template = hibernateTemplate
        SessionFactory sessionFactory = this.sessionFactory
        return (Integer) template.execute { Session session ->
            def q = session.createQuery(query)
            template.applySettings(q)
            def sessionHolder = (SessionHolder) TransactionSynchronizationManager.getResource( sessionFactory )
            if (sessionHolder && sessionHolder.hasTimeout()) {
                q.timeout = sessionHolder.timeToLiveInSeconds
            }

            populateQueryArguments(q, params)
            populateQueryArguments(q, args)
            populateQueryWithNamedArguments(q, params)

            return q.executeUpdate()
        }
    }

    @Override
    Integer executeUpdate(String query, Collection params, Map args) {
        def template = hibernateTemplate
        SessionFactory sessionFactory = this.sessionFactory

        return (Integer) template.execute { Session session ->
            def q = session.createQuery(query)
            template.applySettings(q)
            def sessionHolder = (SessionHolder) TransactionSynchronizationManager.getResource( sessionFactory )
            if (sessionHolder && sessionHolder.hasTimeout()) {
                q.timeout = sessionHolder.timeToLiveInSeconds
            }


            params.eachWithIndex { val, int i ->
                if (val instanceof CharSequence) {
                    q.setParameter i, val.toString()
                }
                else {
                    q.setParameter i, val
                }
            }
            populateQueryArguments(q, args)
            return q.executeUpdate()
        }
    }

    @Override
    Object withSession(Closure callable) {
        GrailsHibernateTemplate template = new GrailsHibernateTemplate(sessionFactory)
        template.setExposeNativeSession(false)
        hibernateTemplate.execute new GrailsHibernateTemplate.HibernateCallback() {
            def doInHibernate(Session session) {
                callable(session)
            }
        }
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    def withNewSession(Closure callable) {
        GrailsHibernateTemplate template  = new GrailsHibernateTemplate(sessionFactory, grailsApplication)
        template.setExposeNativeSession(false)
        SessionHolder sessionHolder = (SessionHolder)TransactionSynchronizationManager.getResource(sessionFactory)
        Session previousSession = sessionHolder?.session
        Session newSession
        boolean newBind = false
        try {
            template.allowCreate = true
            newSession = sessionFactory.openSession()
            if (sessionHolder == null) {
                sessionHolder = new SessionHolder(newSession)
                TransactionSynchronizationManager.bindResource(sessionFactory, sessionHolder)
                newBind = true
            }
            else {
                sessionHolder.@session = newSession
            }

            hibernateTemplate.execute new GrailsHibernateTemplate.HibernateCallback() {
                def doInHibernate(Session session) {
                    callable(session)
                }
            }
        }
        finally {
            try {
                if (newSession) {
                    SessionFactoryUtils.closeSession(newSession)
                }
                if (newBind) {
                    TransactionSynchronizationManager.unbindResource(sessionFactory)
                }
            }
            finally {
                if (previousSession) {
                    sessionHolder.@session = previousSession
                }
            }
        }
    }


}
