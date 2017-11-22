import util.Common
Common.makeFolders(this)

def name = 'sqre/infrastructure/build-layercake'

pipelineJob(name) {
  description('Constructs stack of docker base images for sci-pipe releases.')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
  }

  properties {
    rebuild {
      autoRebuild()
    }
  }

  label('jenkins-master')
  concurrentBuild(true)
  keepDependencies(true)

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
      scriptPath("pipelines/${name.tr('-', '_')}.groovy")
    }
  }
}
