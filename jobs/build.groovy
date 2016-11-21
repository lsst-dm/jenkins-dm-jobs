import util.Common
Common.makeFolders(this)

pipelineJob('release/docker/build') {
  parameters {
    stringParam('TAG', null, 'EUPS distrib tag name to build. Eg. w_2016_08')
    stringParam('PRODUCTS', null, 'Whitespace delimited list of EUPS products to build.')
    booleanParam('PUBLISH', false, 'Push container to docker hub.')
  }

  properties {
    rebuild {
      autoRebuild()
    }
  }

  // don't tie up a beefy build slave
  label('jenkins-master')
  concurrentBuild(false)
  keepDependencies(true)
  concurrentBuild()

  triggers {
    dockerHubTrigger {
      options {
        triggerForAllUsedInJob()
      }
    }
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
      scriptPath('pipelines/newinstall/docker/build.groovy')
    }
  }
}
