import util.Common
Common.makeFolders(this)

pipelineJob('infrastructure/update-cmirror') {
  description('Update S3 mirror of conda packages.')

  properties {
    rebuild {
      autoRebuild()
    }
  }

  label('jenkins-master')
  concurrentBuild(false)
  keepDependencies(true)

  triggers {
    cron('H 5 * * *')
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
      scriptPath('pipelines/infrastructure/update_cmirror.groovy')
    }
  }
}

pipelineJob('infrastructure/build-cmirror') {
  description('Constructs lsstsqre/cmirror container.')

  properties {
    rebuild {
      autoRebuild()
    }
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
      scriptPath('pipelines/infrastructure/build_cmirror.groovy')
    }
  }
}
