import util.Common
Common.makeFolders(this)

def folder = 'sqre/backup'

pipelineJob("${folder}/nightly-sqre-github-snapshot") {
  description('Nighlty mirror clones of all public github repositories.')

  properties {
    rebuild {
      autoRebuild()
    }
  }

  label('jenkins-master')
  keepDependencies(true)
  concurrentBuild(false)

  triggers {
    cron('23 0 * * * ')
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
      scriptPath("pipelines/${folder}/nightly_sqre_github_snapshot.groovy")
    }
  }
}

pipelineJob("${folder}/build-sqre-github-snapshot") {
  description('Constructs a docker image to run sqre-github-snapshot.')

  properties {
    rebuild {
      autoRebuild()
    }
  }

  label('jenkins-master')
  keepDependencies(true)
  concurrentBuild()

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
      scriptPath("pipelines/${folder}/build_sqre_github_snapshot.groovy")
    }
  }
}
