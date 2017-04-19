import util.Common
Common.makeFolders(this)

pipelineJob('release/tarball') {
  description('build and publish EUPS distrib "tarball" packages')

  parameters {
    stringParam('PRODUCT', 'lsst_distrib', 'Whitespace delimited list of EUPS products to build.')
    stringParam('EUPS_TAG', null, 'published EUPS tag')
    booleanParam('RUN_DEMO', false, 'Run the "stack" demo.')
  }

  properties {
    rebuild {
      autoRebuild()
    }
  }

  // don't tie up a beefy build slave
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
      scriptPath('pipelines/release/tarball.groovy')
    }
  }
}
