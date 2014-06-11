package net.fwbrasil.activate

import net.fwbrasil.activate.storage.Storage

trait Tenant {
  val context: ActivateContext
  val storage: Storage[_]
}

