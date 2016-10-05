import util.Common

def name = 'jenkins-dm-jobs'
def org = 'lsst-sqre'
def slug = "${org}/${name}"

folder('ci-ci') {
  description('CI for the CI system(s)')
}

def j = job("ci-ci/${name}") {
  scm {
    git {
      remote {
        github(slug)
        //refspec('+refs/pull/*:refs/remotes/origin/pr/*')
      }
      branch('*/master')
      extensions {
        cloneOptions {
          shallow(true)
        }
      }
    }
  }

  properties {
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
