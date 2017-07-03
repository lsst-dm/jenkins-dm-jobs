/*
derived from:
  https://gist.github.com/akomakom/481507c0dd79ec52a395

which is itself a fork of:
  https://gist.github.com/rb2k/8372402
*/

/**
Jenkins System Groovy script to clean up workspaces on all slaves.

Check if a slave has < X GB of free space, perform cleanup if it's less.
If slave is idle, wipe out everything in the workspace directory as well any extra configured directories.
If slave is busy, wipe out individual job workspace directories for jobs that aren't running.
Either way, remove custom workspaces also if they aren't in use.
**/

import hudson.model.*
import hudson.util.*
import jenkins.model.*
import hudson.FilePath.FileCallable
import hudson.slaves.OfflineCause
import hudson.node_monitors.*

// threshold is in GB and comes from a job parameter
def threshold = 100
// don't clean docker slaves
def skippedLabels = [ 'lsst-dev' ]
// additional paths under slave's root path that should be removed if found
def extraDirectoriesToDelete = []


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
 * find all jobs which are not folders or pipelines
 *
*/
ArrayList allJobs() {
  Jenkins.instance.getAllItems(TopLevelItem).findAll{ item ->
    item instanceof Job && !("${item.class}".contains('WorkflowJob'))
  }
}

def failedNodes = []
def offlineNodes = []
def skippedNodes = []

for (node in Jenkins.instance.nodes) {
  try {
    println "found ${node.displayName}"

    def computer = node.toComputer()
    // a null channel indicates that the node is offline
    if (computer.getChannel() == null) {
      offlineNodes << node
      println "Node ${node.displayName} is offline"
      continue
    }

    if (node.assignedLabels.find{ it.expression in skippedLabels }) {
      skippedNodes << node
      println "Skipping ${node.displayName} based on labels"
      continue
    }

    def size = DiskSpaceMonitor.DESCRIPTOR.get(computer).size
    def roundedSize = size / (1024 * 1024 * 1024) as int

    println("node: " + node.getDisplayName()
            + ", free space: " + roundedSize
            + "GB. Idle: ${computer.isIdle()}")

    def prevOffline = computer.isOffline()
    if (prevOffline && computer.getOfflineCauseReason().startsWith('disk cleanup from job')) {
      // previous run screwed up, ignore it and clear it at the end
      prevOffline = false
    }

    // skip nodes with sufficent disk space
    if (roundedSize >= threshold) {
      skippedNodes << node
      println "Skipping ${node.displayName} based on disk threshhold"
      continue
    }

    // mark node as offline
    if (!prevOffline) {
      //don't override any previosly set temporarily offline causes (set by humans possibly)
      computer.setTemporarilyOffline(true,
        new hudson.slaves.OfflineCause.ByCLI("disk cleanup from job ${build.displayName}")
      )
    }

    // see if any builds are active
    if (computer.isIdle()) {
      // it's idle so delete everything under workspace
      def workspaceDir = node.getWorkspaceRoot()
      if (!deleteRemote(workspaceDir, true)) {
        failedNodes << node
      }

      // delete custom workspaces on the navie assumption that any job could
      // have run on this node in the past

      // select all jobs with a custom workspace
      def custom = allJobs.findAll { item -> item.getCustomWorkspace() }
      custom.each { item ->
        // note that #child claims to deal with abs and rel paths
        if (!deleteRemote(node.getRootPath().child(item.customWorkspace()), false)) {
          failedNodes << node
        }
      }

      extraDirectoriesToDelete.each {
        if (!deleteRemote(node.getRootPath().child(it), false)) {
          failedNodes << node
        }
      }
    } else {
      // node has at least one active job

      // find all jobs that are *not* building
      def idleJobs = allJobs.findAll { item -> !item.isBuilding() }

      idleJobs.each { item ->
        def jobName = item.getFullDisplayName()

        println(".. checking workspaces of job " + jobName)

        workspacePath = node.getWorkspaceFor(item)
        if (!workspacePath) {
          println(".... could not get workspace path for ${jobName}")
          return
        }

        println(".... workspace = " + workspacePath)

        def customWorkspace = item.getCustomWorkspace()
        if (customWorkspace) {
          workspacePath = node.getRootPath().child(customWorkspace)
          println(".... custom workspace = " + workspacePath)
        }

        if (!deleteRemote(workspacePath, false)) {
          failedNodes << node
        }
      }
    }

    if (!prevOffline) {
      computer.setTemporarilyOffline(false, null)
    }
  } catch (Throwable t) {
    println "Error with ${node.displayName}: ${t}"
    failedNodes << node
  }
}

println '### SUMMARY'

failedNodes.each{node ->
  println "\tERRORS with: ${node.displayName}"
}
offlineNodes.each{node ->
  println "\tOffline: ${node.displayName}"
}
skippedNodes.each{node ->
  println "\tSkipped: ${node.displayName}"
}

assert failedNodes.size() == 0 : "\nFailed: ${failedNodes.size()}"
