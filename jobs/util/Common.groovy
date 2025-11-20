package util

import javaposse.jobdsl.dsl.DslFactory
import javaposse.jobdsl.dsl.Folder
import javaposse.jobdsl.dsl.Job

class Common {

  static void addNotification(Job job) {
    job.with {
      publishers {
        /*
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
          customMessage('(<${RUN_DISPLAY_URL}/console|Console>)')
          sendAs(null)
          commitInfoChoice('NONE')
          teamDomain(null)
          authToken(null)
        }
        */
      }
    }
  }

  static void makeFolders(DslFactory dslFactory) {
    dslFactory.folder('sqre') {
      description('SQRE mission related jobs')
    }

    dslFactory.folder('sqre/infra') {
      description('Infrastructure jobs')
    }

    dslFactory.folder('sqre/ci-ci') {
      description('CI for the CI system(s)')
    }

    dslFactory.folder('sqre/backup') {
      description('SQRE service backup(s)')
    }

    dslFactory.folder('release') {
      description('Jobs related to software release management.')
    }

    dslFactory.folder('release/docker') {
      description('Binary releases via docker contrainers.')
    }

    dslFactory.folder('release/codekit') {
      description('Run commands from sqre-codekit.')
    }

    /*
    dslFactory.folder('sqre/cowboy') {
      description('Experimental, not fully-baked, and/or "demonstration purposes only" jobs.')
    }
    */
    dslFactory.folder('dax') {
      description('dax specific jobs.')
    }
    dslFactory.folder('dax/docker') {
      description('Construct docker containers.')
    }
    dslFactory.folder('dax/release') {
      description('Jobs related to dax releases.')
    }
    dslFactory.folder('sims') {
      description('LSST sims specific jobs.')
    }
    dslFactory.folder('scipipe') {
      description('Science Pipelines / witchcraft.')
    }
  }

}
