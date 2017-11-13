import util.Common
Common.makeFolders(this)

def folder = 'sqre/ci-ci'

pipelineJob("${folder}/test-docker-gitlfs-outside") {
  properties {
    rebuild {
      autoRebuild()
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
      scriptPath("pipelines/sqre/ci_ci/test_docker_gitlfs_outside.groovy")
    }
  }
}
