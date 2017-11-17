import util.Common
Common.makeFolders(this)

def folder = 'sqre/ci-ci'

pipelineJob("${folder}/bench-gitlfs") {
  description('Benchmark git lfs version.')

  parameters {
    stringParam('LFS_VER', '2.3.4', 'git lfs version')
    stringParam('RUNS', '3', 'number of repeated benchmarking runs')
  }

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
