/*
derived from:
  https://gist.github.com/akomakom/481507c0dd79ec52a395

which is itself a fork of:
  https://gist.github.com/rb2k/8372402
*/

/**
Jenkins System Groovy script to clean up workspaces on all slaves.

Check if a slave has < X GB of free space, perform cleanup if it's less.  If
slave is idle, wipe out everything in the workspace directory as well any extra
configured directories.  If slave is busy, wipe out individual job workspace
directories for jobs that aren't running.  Either way, remove custom workspaces
also if they aren't in use.
**/

import groovy.transform.Field
import groovy.transform.InheritConstructors
import hudson.FilePath.FileCallable
import hudson.model.*
import hudson.node_monitors.*
import hudson.slaves.OfflineCause
import hudson.util.*
import jenkins.model.*

@InheritConstructors
class CleanupException extends Exception {}

@InheritConstructors
class Node extends CleanupException {
  hudson.model.Slave node

  Node(hudson.model.Slave node) {
    super()
    this.node = node
  }

  Node(hudson.model.Slave node, String m) {
    super(m)
    this.node = node
  }

  Node(hudson.model.Slave node, String m, Throwable t) {
    super(m, t)
    this.node = node
  }
}

@InheritConstructors
class Failed extends Node {}
@InheritConstructors
class Offline extends Node {}
@InheritConstructors
class Skipped extends Node {}
// note that this "exception" is being [ab]used to signal success
@InheritConstructors
class Cleaned extends Node {}

// threshold is in GB and comes from a job parameter
@Field Integer threshold = 100
// skip node if it has any of these labels
@Field List skippedLabels = [
//  'snowflake',
]
// additional paths under slave's root path that should be removed if found
@Field List extraDirectoriesToDelete = [
  'snowflake',
]
// if a cleanup should be run (ignoring threshold)
@Field Boolean forceCleanup = false
// accounting of node status (Cleaned, Failed, etc.)
@Field Map nodeStatus = [:].withDefault {[]}

def deleteRemote(def path, boolean deleteContentsOnly) {
  boolean result = true
  def pathAsString = path.getRemote()
  if (path.exists()) {
    try {
      if (deleteContentsOnly) {
        path.deleteContents()
        println ".... deleted ALL contents of ${pathAsString}"
      } else {
        path.deleteRecursive()
        println ".... deleted directory ${pathAsString}"
      }
    } catch (Throwable t) {
      println "Failed to delete ${pathAsString}: ${t}"
      result = false
    }
  }
  return result
}

/*
 * find all jobs which are not folders
 *
*/
ArrayList allJobs() {
  Jenkins.instance.getAllItems(TopLevelItem).findAll{ item ->
    item instanceof Job && !isFolder(item)
  }
}

/*
 * find all jobs which have a custom workspace
*/
ArrayList customWorkspaceJobs() {
  allJobs().findAll { item ->
    hasCustomWorkspace(item)
  }
}

/*
 * test if Job has a custom workspace
*/
Boolean hasCustomWorkspace(Job item) {
  // pipelines do not have #getCustomWorkspace()
  !isWorkflowJob(item) && item.getCustomWorkspace()
}

Boolean isFolder(Job item) {
  "${item.class}".contains('Folder')
}

Boolean isWorkflowJob(Job item) {
  "${item.class}".contains('WorkflowJob')
}

/*
 * cleanup a node that does not have any active builds. The workspace root and
 * any "extra" paths will be removed.
*/
void cleanupIdleNode(hudson.model.Slave node) {
  // it's idle so delete everything under workspace
  def workspaceDir = node.getWorkspaceRoot()
  if (!deleteRemote(workspaceDir, true)) {
    throw new Failed(node, "delete failed")
  }

  // delete custom workspaces on the naive assumption that any job could
  // have run on this node in the past

  // select all jobs with a custom workspace
  customWorkspaceJobs().each { item ->
    // note that #child claims to deal with abs and rel paths
    if (!deleteRemote(
          node.getRootPath().child(item.customWorkspace()),
          false
    )) {
      throw new Failed(node, "delete failed")
    }
  }

  // do not cleanup extra paths unless the entire node is idle as it is
  // possible that a running build on an active node could decide to use it.
  extraDirectoriesToDelete.each {
    if (!deleteRemote(node.getRootPath().child(it), false)) {
      throw new Failed(node, "delete failed")
    }
  }
}

/*
 * cleanup a node that has atleast one active builds.
*/
void cleanupBusyNode(hudson.model.Slave node) {
  // node has at least one active job
  println(". looking for idle job workspaces on ${node.getDisplayName()}")

  // find all jobs that are *not* building
  //
  // Note that will miss jobs which have been deleted but still have a
  // workspace on disk.
  def idleJobs = allJobs().findAll { item -> !item.isBuilding() }

  idleJobs.each { item ->
    def jobName = item.getFullDisplayName()

    println(".. checking workspaces of job " + jobName)

    workspacePath = node.getWorkspaceFor(item)
    if (!workspacePath) {
      println("... could not get workspace path for ${jobName}")
      return
    }

    println("... workspace = " + workspacePath)

    if (hasCustomWorkspace(item)) {
      workspacePath = node.getRootPath().child(item.getCustomWorkspace())
      println("... custom workspace = " + workspacePath)
    }

    if (!deleteRemote(workspacePath, false)) {
      throw new Failed(node, "delete failed")
    }
  }
}

/*
 * parse jenkins job parameters being passed in
*/
void parseParams() {
  // Retrieve parameters of the current build
  def resolver = build.buildVariableResolver

  forceCleanup = resolver.resolve('FORCE_CLEANUP').toBoolean()
  threshold = resolver.resolve('CLEANUP_THRESHOLD').toInteger()
  println '### PARAMETERS'
  println "CLEANUP_THRESHOLD=$threshold"
  println "FORCE_CLEANUP=$forceCleanup"
  println ''
}

/*
 * iterate over nodes and cleanup
*/
void processNodes() {
  def prevOffline = false

  println '### NODES'
  for (node in Jenkins.instance.nodes) {
    try {
      println "found ${node.displayName}"

      // toComputer() can return `null` if the node has no executors but
      // Jenkins.instance.nodes() does not seem to return nodes with no
      // executors.
      def computer = node.toComputer()

      // a null channel indicates that the node is offline
      if (computer == null || computer.getChannel() == null) {
        throw new Offline(node)
      }

      if (node.assignedLabels.find{ it.expression in skippedLabels }) {
        throw new Skipped(node, "based on label(s)")
      }

      def dsm = DiskSpaceMonitor.DESCRIPTOR.get(computer)
      def roundedSize = null
      if (dsm) {
        def size = dsm.size
        roundedSize = size / (1024 * 1024 * 1024) as int
      } else {
        println "unable to determine disk usage (this is bad)"

        // force disk cleanup
        roundedSize = 0
        println "assuming free disk space == 0 to force cleanup"
      }

      println("node: " + node.getDisplayName()
              + ", free space: " + roundedSize
              + "GB. Idle: ${computer.isIdle()}")

      prevOffline = computer.isOffline()
      if (prevOffline &&
          computer.getOfflineCauseReason().startsWith('disk cleanup from job')) {
        // previous run screwed up, ignore it and clear it at the end
        prevOffline = false
      }

      // skip nodes with sufficient disk space
      if (!forceCleanup && (roundedSize >= threshold)) {
        throw new Skipped(node, "disk threshold")
      }

      // mark node as offline
      if (!prevOffline) {
        // don't override any previously set temporarily offline causes (set by
        // humans possibly)
        def cleanupMsg = "disk cleanup from job ${build.displayName}"
        computer.setTemporarilyOffline(
          true,
          new hudson.slaves.OfflineCause.ByCLI(cleanupMsg)
        )
      }

      // see if any builds are active
      if (computer.isIdle()) {
        cleanupIdleNode(node)
      } else {
        cleanupBusyNode(node)
      }

      // signal success
      throw new Cleaned(node, "OK")
    } catch (Node t) {
      switch (t) {
        case Cleaned:
          nodeStatus['cleanedNodes'] << t
          break
        case Offline:
          nodeStatus['offlineNodes'] << t
          break
        case Skipped:
          nodeStatus['skippedNodes'] << t
          break
        default:
          // includes Failed
          nodeStatus['failedNodes'] << t
      }
    } finally {
      if (!prevOffline) {
        node.toComputer().setTemporarilyOffline(false, null)
      }

      println ''
    }
  }
}

/*
 * print a status summary
*/
void printSummary() {
  println '### SUMMARY'

  nodeStatus.each { status ->
    println "  * ${status.key}:"

    status.value.each { e ->
      println "    - ${e.node.displayName} ${e}"
    }
  }

  def borked = nodeStatus['failedNodes']?.size()
  assert borked == 0 : "\nFailed: ${borked}"
}

/*
 * main entry point
*/
void go() {
  parseParams()
  processNodes()
  printSummary()
}

go()
