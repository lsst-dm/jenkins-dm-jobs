import util.Common
Common.makeFolders(this)

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
  }

  steps {
    shell('./gradlew test')
  }
}

Common.addNotification(j)
