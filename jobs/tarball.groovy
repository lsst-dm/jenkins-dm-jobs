import util.Common
Common.makeFolders(this)

pipelineJob('release/tarball') {
  description('build and publish EUPS distrib "tarball" packages')

  parameters {
    stringParam('PRODUCT', 'lsst_distrib', 'Whitespace delimited list of EUPS products to build.')
    stringParam('EUPS_TAG', null, 'published EUPS tag')
    booleanParam('SMOKE', false, 'Run a post-build installation test of generated EUPS distrib traballs.')
    booleanParam('RUN_DEMO', false, '(no-op without SMOKE) Run the "stack" demo as part of the "smoke" installation test.')
    booleanParam('RUN_SCONS_CHECK', false, '(no-op without SMOKE) Manually checkout the "base" product and invoke "scons".')
    booleanParam('PUBLISH', false, 'Publish generated EUPS distrib tarballs.')
    booleanParam('WIPEOUT', false, 'Completely wipe out workspace(s) before starting build.')
    choiceParam('PYVER', ['3', '2'], 'Python major version')
    choiceParam('MINIVER', ['4.3.21', '4.2.12'], 'Miniconda installer version')
    choiceParam('LSSTSW_REF', ['10a4fa6', '7c8e67'], 'LSST conda package set ref')
    choiceParam('OS', ['centos-7', 'centos-6', 'osx-10.11'], 'LSST conda package set ref')
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
      scriptPath('pipelines/release/tarball.groovy')
    }
  }
}
