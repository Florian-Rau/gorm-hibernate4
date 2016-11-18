package grails.test.hibernate

import grails.config.Config
import groovy.transform.CompileStatic
import org.grails.config.PropertySourcesConfig
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.orm.hibernate.cfg.Settings
import org.springframework.boot.env.PropertySourcesLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.interceptor.DefaultTransactionAttribute
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Specification for Hibernate tests
 *
 * @author Graeme Rocher
 * @since 6.0
 */
@CompileStatic
abstract class HibernateSpec extends Specification {
    @Shared @AutoCleanup HibernateDatastore hibernateDatastore
    @Shared PlatformTransactionManager transactionManager

    void setupSpec() {
        PropertySourcesLoader loader = new PropertySourcesLoader()
        ResourceLoader resourceLoader = new DefaultResourceLoader()
        MutablePropertySources propertySources = loader.propertySources
        propertySources.addFirst(new MapPropertySource("defaults", getConfiguration()))
        loader.load resourceLoader.getResource("application.yml")
        loader.load resourceLoader.getResource("application.groovy")
        Config config = new PropertySourcesConfig(propertySources)
        List<Class> domainClasses = getDomainClasses()
        String packageName = getPackageToScan(config)

        if (!domainClasses) {
            hibernateDatastore = new HibernateDatastore(
                    DatastoreUtils.createPropertyResolver(getConfiguration()),
                    Package.getPackage(packageName))
        }
        else {
            hibernateDatastore = new HibernateDatastore(
                    DatastoreUtils.createPropertyResolver(getConfiguration()),
                    domainClasses as Class[])
        }
        transactionManager = hibernateDatastore.getTransactionManager()
    }

    /**
     * The transaction status
     */
    TransactionStatus transactionStatus

    void setup() {
        transactionStatus = transactionManager.getTransaction(new DefaultTransactionAttribute())
    }

    void cleanup() {
        if(isRollback()) {
            transactionManager.rollback(transactionStatus)
        }
        else {
            transactionManager.commit(transactionStatus)
        }
    }

    /**
     * @return The configuration
     */
    Map getConfiguration() {
        Collections.singletonMap(Settings.SETTING_DB_CREATE, "create-drop")
    }

    /**
     * Whether to rollback on each test (defaults to true)
     */
    boolean isRollback() {
        return true
    }

    /**
     * @return The domain classes
     */
    List<Class> getDomainClasses() { [] }

    /**
     * Obtains the default package to scan
     *
     * @param config The configuration
     * @return The package to scan
     */
    protected String getPackageToScan(Config config) {
        config.getProperty('grails.codegen.defaultPackage', getClass().package.name)
    }
}
