def name = 'jenkins-dm-jobs'
def org = 'jhoblitt'
def slug = "${org}/${name}"

folder('ci-ci') {
  description('CI for the CI system(s)')
}

job("ci-ci/${name}") {
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
    pullRequest {
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

  publishers {
    // must be defined even to use the global defaults
    hipChat {}
  }
}
