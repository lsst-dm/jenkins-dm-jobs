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
import hudson.FilePath
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
  Slave node

  Node(Slave node) {
    super()
    this.node = node
  }

  Node(Slave node, String m) {
    super(m)
    this.node = node
  }

  Node(Slave node, String m, Throwable t) {
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

@Field debug = true
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

boolean deleteRemote(FilePath path, boolean deleteContentsOnly) {
  boolean result = true
  String rPath = path.getRemote()

  if (path.exists()) {
    try {
      if (deleteContentsOnly) {
        path.deleteContents()
        println ".... deleted ALL contents of ${rPath}"
      } else {
        path.deleteRecursive()
        println ".... deleted directory ${rPath}"
      }
    } catch (Throwable t) {
      println "Failed to delete ${rPath}: ${t}"
      result = false
    }
  }
  return result
}

/*
 * println debug message
*/
void debugln(Object value) {
 debug && println(value)
}

/*
 * find all jobs which are not folders
 *
*/
ArrayList allJobs() {
  Jenkins.instance.getAllItems(TopLevelItem).findAll {
    it instanceof Job && !isFolder(it)
  }
}

/*
 * find all jobs which have a custom workspace
*/
ArrayList customWorkspaceJobs() {
  allJobs().findAll { hasCustomWorkspace(it) }
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
 * find all jobs that *are* building on node
*/
ArrayList findBusyJobsByNode(Slave node) {
  def computer = node.toComputer()

  // find currently busy executors
  def busyExecs = computer.getExecutors().findAll { it.isBusy() }

  // find jobs with a build active on one of this node's executors
  // is it a race condition if the build finishes before getCurrentExecutable()
  // is called?
  busyExecs.collect { it.getCurrentExecutable().getProject() }
}

/*
 * find all jobs that *are not* building on node
*/
ArrayList findIdleJobsByNode(Slave node) {
  def busyJobs = findBusyJobsByNode(node)

  // filter out active jobs
  allJobs().findAll { job ->
    !busyJobs.find { busy ->
      busy.getFullName() == job.getFullName()
    }
  }
}

/*
 * If job has a custom workspace defined, remove it
*/
void deleteCustomWorkspace(Job job, Slave node) {
  if (!hasCustomWorkspace(job)) {
    return
  }

  // note that #child claims to deal with abs and rel paths
  def wsPath = node.getRootPath().child(job.getCustomWorkspace())
  debugln("... custom workspace = ${wsPath}")

  if (!deleteRemote(wsPath, false)) {
    throw new Failed(node, 'delete failed')
  }
}

/*
 * cleanup a node that does not have any active builds. The workspace root and
 * any "extra" paths will be removed.
*/
void cleanupIdleNode(Slave node) {
  debugln(". cleaning up node: ${node.getDisplayName()}")
  // it's idle so delete everything under workspace
  def workspaceDir = node.getWorkspaceRoot()
  debugln ".. root workspace = ${workspaceDir}"

  if (!deleteRemote(workspaceDir, true)) {
    throw new Failed(node, 'delete failed')
  }

  // delete custom workspaces on the naive assumption that any job could
  // have run on this node in the past
  debugln('.. looking for custom workspaces')
  customWorkspaceJobs().each { job ->
    debugln("... job ${job.getFullName()}")
    deleteCustomWorkspace(job, node)
  }

  // do not cleanup extra paths unless the entire node is idle as it is
  // possible that a running build on an active node could decide to use it.
  debugln('.. looking for "extra" directories')
  extraDirectoriesToDelete.each { extra ->
    def path = node.getRootPath().child(extra)
    debugln("... extra dir = ${path}")

    if (!deleteRemote(path, false)) {
      throw new Failed(node, 'delete failed')
    }
  }
}

/*
 * cleanup a node that has atleast one active builds.
 *
 * Note that this will miss jobs which have been deleted but still have a
 * workspace on disk.
*/
void cleanupBusyNode(Slave node) {
  debugln(". cleaning up node: ${node.getDisplayName()}")

  println('.. active builds:')
  findBusyJobsByNode(node).each {
    println "... build of = ${it.getFullName()}"
  }

  debugln('.. idle job workspaces:')
  findIdleJobsByNode(node).each { job ->
    def jobName = job.getFullDisplayName()

    debugln("... checking workspaces of job ${jobName}")
    // is it possible for a job to have both a "regular" and "custom"
    // workspace???
    def wsPath = node.getWorkspaceFor(job)
    debugln("... workspace = ${wsPath}")
    if (!deleteRemote(wsPath, false)) {
      throw new Failed(node, 'delete failed')
    }

    deleteCustomWorkspace(job, node)
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
  println "CLEANUP_THRESHOLD=${threshold}"
  println "FORCE_CLEANUP=${forceCleanup}"
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
      if (computer == null || computer.isOffline()) {
        throw new Offline(node)
      }

      if (node.assignedLabels.find{ it.expression in skippedLabels }) {
        throw new Skipped(node, 'based on label(s)')
      }

      def dsm = DiskSpaceMonitor.DESCRIPTOR.get(computer)
      def roundedSize = null
      if (dsm) {
        def size = dsm.size
        roundedSize = size / (1024 * 1024 * 1024) as int
      } else {
        println 'unable to determine disk usage (this is bad)'

        // force disk cleanup
        roundedSize = 0
        println 'assuming free disk space == 0 to force cleanup'
      }

      println("node: ${node.getDisplayName()}"
              + ", free space: ${roundedSize} GiB"
              + ", idle: ${computer.isIdle()}")

      prevOffline = computer.isOffline()
      if (prevOffline &&
          computer.getOfflineCauseReason().startsWith('disk cleanup from job')) {
        // previous run screwed up, ignore it and clear it at the end
        prevOffline = false
      }

      // skip nodes with sufficient disk space
      if (!forceCleanup && (roundedSize >= threshold)) {
        throw new Skipped(node, 'disk threshold')
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
        // node has at least one active build
        cleanupBusyNode(node)
      }

      // signal success
      throw new Cleaned(node, 'OK')
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
