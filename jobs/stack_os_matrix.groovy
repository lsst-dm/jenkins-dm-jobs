import util.Common

def j = matrixJob('stack-os-matrix') {
  parameters {
    stringParam('BRANCH', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCT', 'lsst_sims lsst_distrib', 'Whitespace delimited list of EUPS products to build.')
    booleanParam('SKIP_DEMO', false, 'Do not run the demo after all packages have completed building.')
    booleanParam('NO_FETCH', false, 'Do not pull from git remote if branch is already the current ref. (This should generally be false outside of testing the CI system)')
  }

  configure { project ->
    project / 'properties' / 'hudson.model.ParametersDefinitionProperty' / 'parameterDefinitions' / 'com.cwctravel.hudson.plugins.extended__choice__parameter.ExtendedChoiceParameterDefinition' {
      name 'python'
      description 'Python environment in which to build (multiple choice)'
      quoteValue false
      saveJSONParameterToFile false
      visibleItemCount 2
      type 'PT_MULTI_SELECT'
      value 'py2, py3'
      defaultValue 'py2'
      multiSelectDelimiter ' '
    }
  }

  properties {
    rebuild {
      autoRebuild()
    }
  }

  label('master')
  concurrentBuild()

  multiscm {
    git {
      remote {
        github('lsst/lsstsw')
      }
      branch('*/master')
      extensions {
        relativeTargetDirectory('lsstsw')
        cloneOptions { shallow() }
      }
    }
    git {
      remote {
        github('lsst-sqre/buildbot-scripts')
      }
      branch('*/master')
      extensions {
        relativeTargetDirectory('buildbot-scripts')
        cloneOptions { shallow() }
      }
    }
  }

  axes {
    label('label', 'centos-6', 'centos-7')
    dynamicAxis {
      name('python')
      varName('python')
    }
  }

  combinationFilter('!(label=="centos-6" && python=="py3")')

  wrappers {
    colorizeOutput('gnome-terminal')
  }

  environmentVariables(
    SKIP_DOCS: true,
  )

  steps {
    shell('./buildbot-scripts/jenkins_wrapper.sh')
  }

  publishers {
    postBuildScripts {
      onlyIfBuildFails(true)
      onlyIfBuildSucceeds(false)
      steps {
        shell(
          '''
          Z=$(lsof -d 200 -t)
          if [[ ! -z $Z ]]; then
            kill -9 $Z
          fi

          rm -rf "${WORKDIR}/lsstsw/stack/.lockDir"
          '''.replaceFirst("\n","").stripIndent()
        )
      }
    }
    archiveArtifacts {
      fingerprint()
      pattern('lsstsw/build/manifest.txt')
    }
  }
}

Common.addNotification(j)
