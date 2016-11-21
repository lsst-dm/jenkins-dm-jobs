import util.Common
Common.makeFolders(this)

pipelineJob('release/docker/newinstall') {
  properties {
    rebuild {
      autoRebuild()
    }
  }

  // don't tie up a beefy build slave
  label('jenkins-master')
  concurrentBuild(false)
  keepDependencies(true)

  triggers {
    dockerHubTrigger {
      options {
        triggerForAllUsedInJob()
      }
    }
    upstream('release/docker/prepare', 'UNSTABLE')
  }

  def repo = SEED_JOB.scm.userRemoteConfigs.get(0).getUrl()
  def ref  = SEED_JOB.scm.getBranches().get(0).getName()

  definition {
    cpsScm {
      scm {
        git {
          remote {
            url(repo)
          }
          branch(ref)
        }
      }
      scriptPath('pipelines/newinstall/docker/newinstall.groovy')
    }
  }
}
