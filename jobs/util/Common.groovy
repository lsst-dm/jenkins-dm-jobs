package util

import javaposse.jobdsl.dsl.Job

class Common {
  static void addNotification(Job job) {
    job.with {
      publishers {
        // must be defined even to use the global defaults
        hipChat {}
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
          includeCustomMessage(false)
          customMessage(null)
          buildServerUrl(null)
          sendAs(null)
          commitInfoChoice('NONE')
          teamDomain(null)
          authToken(null)
        }
      }
    }
  }
}
