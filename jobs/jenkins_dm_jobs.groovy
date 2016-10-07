import util.Common

folder('ci-ci') {
  description('CI for the CI system(s)')
}

def j = job('ci-ci/jenkins-dm-jobs') {
  def repo = SEED_JOB.scm.userRemoteConfigs.get(0).getUrl()
  def ref  = SEED_JOB.scm.getBranches().get(0).getName()

  scm {
    git {
      remote {
        url(repo)
      }
      branch(ref)
      extensions {
        cloneOptions {
          shallow(true)
        }
      }
    }
  }

  properties {
    githubProjectUrl(repo)
    rebuild {
      autoRebuild()
    }
  }
  concurrentBuild()

  triggers {
    githubPush()
    githubPullRequest {
      cron('H/5 * * * *')
      useGitHubHooks()
      permitAll()
      // configure credential to use for GH API
      configure { project ->
        project / 'triggers' / 'org.jenkinsci.plugins.ghprb.GhprbTrigger' {
          gitHubAuthId 'github-api-token-jhoblitt'
        }
      }
    }
  }

  steps {
    shell('./gradlew test')
  }
}

Common.addNotification(j)
