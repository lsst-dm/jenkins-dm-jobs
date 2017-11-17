import util.Common
Common.makeFolders(this)

def folder = 'sqre/ci-ci'

pipelineJob("${folder}/bench-gitlfs") {
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
      scriptPath("pipelines/sqre/ci_ci/bench_gitlfs.groovy")
    }
  }
}
