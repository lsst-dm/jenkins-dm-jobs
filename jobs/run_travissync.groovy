import util.Common
Common.makeFolders(this)

def folder = 'sqre/infrastructure'

pipelineJob("${folder}/travissync") {
  description('Synchronize Travis CI with GitHub.')

  properties {
    rebuild {
      autoRebuild()
    }
  }

  label('travissync')
  keepDependencies(true)
  concurrentBuild()

  def repo = SEED_JOB.scm.userRemoteConfigs.get(0).getUrl()
  def ref  = SEED_JOB.scm.getBranches().get(0).getName()

  triggers {
    cron('H * * * *')
  }

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
      scriptPath("pipelines/${folder}/travissync.groovy")
    }
  }
}
