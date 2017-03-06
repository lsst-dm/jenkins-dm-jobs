package util

import javaposse.jobdsl.dsl.DslFactory
import javaposse.jobdsl.dsl.Folder
import javaposse.jobdsl.dsl.Job

class Common {
  static void addNotification(Job job) {
    job.with {
      publishers {
        slackNotifier {
          room(null)
          notifyAborted(true)
          notifyFailure(true)
          notifyNotBuilt(true)
          notifyUnstable(false)
          notifyBackToNormal(false)
          notifySuccess(true)
          notifyRepeatedFailure(false)
          startNotification(true)
          includeTestSummary(false)
          includeCustomMessage(true)
          customMessage('(<${BUILD_URL}/console|Console>)')
          buildServerUrl(null)
          sendAs(null)
          commitInfoChoice('NONE')
          teamDomain(null)
          authToken(null)
        }
      }
    }
  }

  static void makeFolders(DslFactory dslFactory) {
    dslFactory.folder('ci-ci') {
      description('CI for the CI system(s)')
    }

    dslFactory.folder('backup') {
      description('SQRE service backup(s)')
    }

    dslFactory.folder('release') {
      description('Jobs related to software release management.')
    }

    dslFactory.folder('release/docker') {
      description('Binary releases via docker contrainers.')
    }

    dslFactory.folder('cowboy') {
      description('Experimental, not fully-baked, and/or "demonstration purposes only" jobs.')
    }
    dslFactory.folder('qserv') {
      description('qserv specific jobs.')
    }
    dslFactory.folder('qserv/docker') {
      description('Construct docker containers.')
    }
    dslFactory.folder('qserv/release') {
      description('Jobs related to DAX/qserv releases.')
    }
    dslFactory.folder('sims') {
      description('LSST sims specific jobs.')
    }
  }
}
