import util.Common
Common.makeFolders(this)

def name = 'sqre/infrastructure/infra-monthly-cron'

pipelineJob(name) {
  description('Periodic monthly builds of infrastructure jobs.')

  properties {
    rebuild {
      autoRebuild()
    }
  }

  triggers {
    cron('0 0 1 * *')
  }

  label('jenkins-master')
  concurrentBuild(false)
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
