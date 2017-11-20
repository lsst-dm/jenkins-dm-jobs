import util.Common
Common.makeFolders(this)

def name = 'sqre/infrastructure/build-codekit'

pipelineJob(name) {
  description('Constructs docker sqre-codekit images.')

  parameters {
    stringParam('CODEKIT_VER', '3.1.0', 'sqre-codekit pypi version to install')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', false, 'Also push to docker registry with "latest" tag.')
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
