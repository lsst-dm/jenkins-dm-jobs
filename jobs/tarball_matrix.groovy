import util.Common
Common.makeFolders(this)

pipelineJob('release/tarball-matrix') {
  description('build and publish EUPS distrib "tarball" packages')

  parameters {
    stringParam('PRODUCT', 'lsst_distrib', 'Whitespace delimited list of EUPS products to build.')
    stringParam('EUPS_TAG', null, 'published EUPS tag')
    booleanParam('SMOKE', true, 'Run a post-build installation test of generated EUPS distrib traballs.')
    booleanParam('RUN_DEMO', true, '(no-op without SMOKE) Run the "stack" demo as part of the "smoke" installation test.')
    booleanParam('RUN_SCONS_CHECK', true, '(no-op without SMOKE) Manually checkout the "base" product and invoke "scons".')
    booleanParam('PUBLISH', false, 'Publish generated EUPS distrib tarballs.')
    booleanParam('WIPEOUT', false, 'Completely wipe out workspace(s) before starting build.')
  }

  properties {
    rebuild {
      autoRebuild()
    }
  }

  // don't tie up a beefy build slave
  label('jenkins-master')
  concurrentBuild(true)
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
      scriptPath('pipelines/release/tarball_matrix.groovy')
    }
  }
}
