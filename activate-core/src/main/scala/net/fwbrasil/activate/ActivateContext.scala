package net.fwbrasil.activate

import language.implicitConversions
import net.fwbrasil.scala.UnsafeLazy._
import net.fwbrasil.activate.storage.Storage
import net.fwbrasil.activate.statement.mass.MassDeleteContext
import net.fwbrasil.activate.entity.EntityContext
import net.fwbrasil.activate.cache.LiveCache
import net.fwbrasil.activate.entity.EntityHelper
import net.fwbrasil.activate.statement.query.QueryContext
import net.fwbrasil.activate.statement.mass.MassUpdateContext
import net.fwbrasil.activate.statement.query.QueryNormalizer
import net.fwbrasil.activate.migration.StorageAction
import net.fwbrasil.activate.migration.Migration
import net.fwbrasil.activate.entity.BaseEntity
import net.fwbrasil.activate.statement.query.Query
import net.fwbrasil.activate.util.Logging
import net.fwbrasil.activate.serialization.NamedSingletonSerializable
import scala.collection.mutable.{ HashMap => MutableHashMap }
import net.fwbrasil.activate.serialization.NamedSingletonSerializable.instancesOf
import net.fwbrasil.activate.util.RichList._
import net.fwbrasil.activate.serialization.Serializer
import net.fwbrasil.activate.statement.StatementMocks
import net.fwbrasil.activate.serialization.SerializationContext
import net.fwbrasil.activate.util.CollectionUtil
import net.fwbrasil.activate.migration.MigrationContext
import net.fwbrasil.activate.util.Reflection
import java.util.concurrent.ConcurrentHashMap
import net.fwbrasil.activate.index.MemoryIndexContext
import net.fwbrasil.activate.index.ActivateIndexContext
import net.fwbrasil.activate.entity.id.CustomID
import net.fwbrasil.activate.entity.id.UUID
import net.fwbrasil.activate.entity.BaseEntity
import net.fwbrasil.activate.entity.id.EntityId

trait ActivateContext
        extends EntityContext
        with QueryContext
        with MassUpdateContext
        with MassDeleteContext
        with NamedSingletonSerializable
        with Logging
        with DelayedInit
        with DurableContext
        with StatementsContext
        with SerializationContext
        with MigrationContext
        with ActivateIndexContext {

    info("Initializing context " + contextName)

    private[activate] val properties =
        new ActivateProperties(None, Some("activate"))

    implicit val context = this: this.type

    val storage: Storage[_]

    var _tenants: Set[Tenant] = Set()

    def tenants = _tenants

    def all = tenants + TenantAdmin

    def createTenant(mkStorage: => Storage[_]): Tenant = {
      val tenant = new Tenant {
        val context = ActivateContext.this.context
        val storage = mkStorage
      }
      _tenants += tenant
      tenant
    }

    implicit object TenantAdmin extends Tenant {
      val context = ActivateContext.this.context
      val storage = ActivateContext.this.storage
    }

    protected[activate] def storages =
        (additionalStorages.keys.toSet + storage).toList

    private[activate] def storageFor(query: Query[_])(implicit tenant: Tenant): Storage[_] =
      if(tenant == TenantAdmin) {
        query.from.entitySources.map(source => storageFor(source.entityClass))
            .toSet.onlyOne(storages => s"Query $query uses entities from different storages: $storages.")
      }
      else {
        tenant.storage
      }

    private[activate] def storageFor(entityClass: Class[_])(implicit tenant: Tenant): Storage[Any] =
      if(tenant == TenantAdmin) {
        additionalStoragesByEntityClasses.getOrElse(
            entityClass.asInstanceOf[Class[BaseEntity]],
            storage.asInstanceOf[Storage[Any]])
      }
      else {
        tenant.storage
      }

    private val additionalStoragesByEntityClasses =
        unsafeLazy((for ((storage, classes) <- additionalStorages.asInstanceOf[Map[Storage[Any], Set[Class[BaseEntity]]]]) yield classes.map((_, storage))).flatten.toMap)

    def additionalStorages = Map[Storage[_], Set[Class[_ <: BaseEntity]]]()

    def reinitializeContext =
        logInfo("reinitializing context " + contextName) {
            clearCachedQueries
            liveCache.reinitialize
            storages.foreach(_.reinitialize)
            reinitializeIdGenerators
            unloadIndexes
        }

    def currentTransaction =
        transactionManager.getRequiredActiveTransaction

    private[activate] def name = contextName

    def contextName =
        this.getClass.getSimpleName.split('$').last

    def acceptEntity(entityClass: Class[_]) =
        contextEntities.map(_.contains(entityClass)).getOrElse(true)

    protected val contextEntities: Option[List[Class[_ <: BaseEntity]]] = None
    
    override def toString = "ActivateContext: " + name

}

object ActivateContext {

    private var currentClassLoader = this.getClass.getClassLoader

    private[activate] def setClassLoader(classLoader: ClassLoader) = {
        if (currentClassLoader != classLoader)
            clearCaches(true)
        currentClassLoader = classLoader
    }

    private[activate] def loadClass(className: String) =
        classLoaderFor(className).loadClass(className)

    private[activate] def classLoaderFor(className: String) =
        if (className.startsWith("net.fwbrasil.activate"))
            classOf[BaseEntity].getClassLoader()
        else
            currentClassLoader
            
    private[activate] val contextCache =
        new ConcurrentHashMap[Class[_], ActivateContext]()

    private[activate] def contextFor[E <: BaseEntity](entityClass: Class[E]) = {
        var context = contextCache.get(entityClass)
        if (context == null) {
            context = this.context(entityClass)
            contextCache.put(entityClass, context)
        }
        context
    }

    def clearCaches(forClassReload: Boolean = false) = {
        Migration.storageVersionCache.clear
        Migration.storageVersionCache.clear
        StatementMocks.entityMockCache.clear
        contextCache.clear
        if (forClassReload) {
            net.fwbrasil.smirror.clearCache
            Migration.migrationsCache.clear
            EntityHelper.clearMetadatas
            Reflection.clearCaches
            EntityId.idClassCache.clear
        }
    }

    private def context[E <: BaseEntity](entityClass: Class[E]) =
        instancesOf[ActivateContext]
            .filter(_.acceptEntity(entityClass))
            .onlyOne(iterable => "\nThere should be one and only one context that accepts " + entityClass + ".\n" +
                "Maybe the context isn't initialized or you must override the contextEntities val on your context.\n" +
                "Important: The context definition must be declared in a base package of the entities packages.\n" +
                "Example: com.app.myContext for com.app.model.MyEntity.\n" +
                "Found contexts: " + iterable.toList)

}
