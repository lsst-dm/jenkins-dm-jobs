import util.Common
Common.makeFolders(this)

pipelineJob('cowboy/stack') {
  description('Re-implementation of the `stack-os-matrix` job in job-dsl & pipeline.  Presently, this job *ONLY* builds on OSX slaves.  Use this job for testing on OSX.')

  parameters {
    stringParam('BRANCH', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCT', 'lsst_distrib', 'Whitespace delimited list of EUPS products to build.')
    booleanParam('SKIP_DEMO', false, 'Do not run the demo after all packages have completed building.')
    booleanParam('NO_FETCH', false, 'Do not pull from git remote if branch is already the current ref. (This should generally be false outside of testing the CI system)')

    activeChoiceParam('python') {
      description('Python environment in which to build (single choice)')
      choiceType('SINGLE_SELECT')
      groovyScript {
        script('["py2:selected", "py3"]')
      }
    }
  }

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
      scriptPath('pipelines/stack.groovy')
    }
  }
}
