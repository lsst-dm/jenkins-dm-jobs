import util.Common
Common.makeFolders(this)

def folder = 'sqre/ci-ci'

pipelineJob("${folder}/test-pipeline-write") {
  description('Test writing and archiving a file from a pipeline script.')

  properties {
    rebuild {
      autoRebuild()
    }
  }

  // don't tie up a beefy build slave
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
      scriptPath("pipelines/sqre/ci_ci/test_pipeline_write.groovy")
    }
  }
}
