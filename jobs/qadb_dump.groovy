import util.Common
Common.makeFolders(this)

pipelineJob('backup/qadb-dump') {
  description('Backup qadb to S3.')

  properties {
    rebuild {
      autoRebuild()
    }
  }

  label('jenkins-master')
  concurrentBuild(false)
  keepDependencies(true)

  triggers {
    cron('H H/4 * * *')
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
      scriptPath('pipelines/backup/qadb_dump.groovy')
    }
  }
}
