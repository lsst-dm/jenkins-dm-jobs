folder('release') {
  description('Jobs related to software release management.')
}

pipelineJob('release/build-publish-tag') {

  parameters {
    stringParam('BRANCH', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCT', null, 'Whitespace delimited list of EUPS products to build.')
    stringParam('TAG', null, 'EUPS distrib/git tag name to publish. Eg. w_2016_08')
    booleanParam('SKIP_DEMO', false, 'Do not run the demo after all packages have completed building.')
    booleanParam('SKIP_DOCS', false, 'Do not build and publish documentation.')
  }

  properties {
    rebuild {
      autoRebuild()
    }
  }

  // don't tie up a beefy build slave
  label('jenkins-master')
  keepDependencies(true)

  definition {
    cpsScm {
      scm {
        github('lsst-sqre/jenkins-dm-jobs', '*/master')
      }
      scriptPath('pipelines/build_publish_tag.groovy')
    }
  }
}
