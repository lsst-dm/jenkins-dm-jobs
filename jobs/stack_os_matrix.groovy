import util.Common

def j = matrixJob('stack-os-matrix') {
  parameters {
    stringParam('BRANCH', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCT', 'lsst_distrib', 'Whitespace delimited list of EUPS products to build.')
    booleanParam('SKIP_DEMO', false, 'Do not run the demo after all packages have completed building.')
    booleanParam('NO_FETCH', false, 'Do not pull from git remote if branch is already the current ref. (This should generally be false outside of testing the CI system)')
  }

  // job-dsl has support for the active choices plugin, which has mutli-select
  // capability, but the variable it generates has comma separated values.
  // While the dynamic axes plugin will only work with space separated values
  // and neither plugin appears to be configurable.  Thus, we are resorting to
  // manually injecting configuration for extended choices plugin, which can be
  // configured to produce space seperated values.
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
    label('label',
      'centos-6', 'centos-7',
      'osx'
    )
    dynamicAxis {
      name('python')
      varName('python')
    }
  }

  combinationFilter('''
    !(
      (label=="centos-6" && python=="py3") ||
      (label=="osx-10.11" && python=="py3")
    )
  '''.replaceFirst("\n","").stripIndent())

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
    // we have to use postBuildScript here instead of the friendlier
    // postBuildScrips (plural) in order to use executeOn(), otherwise the
    // cleanup script is also run on the jenkins master
    postBuildScript {
      scriptOnlyIfSuccess(false)
      scriptOnlyIfFailure(true)
      markBuildUnstable(false)
      executeOn('AXES')
      buildStep {
        shell {
          command(
            '''
            Z=$(lsof -d 200 -t)
            if [[ ! -z $Z ]]; then
              kill -9 $Z
            fi

            rm -rf "${WORKSPACE}/lsstsw/stack/.lockDir"
            '''.replaceFirst("\n","").stripIndent()
          )
        }
      }
    }
    archiveArtifacts {
      fingerprint()
      pattern('lsstsw/build/manifest.txt')
    }
  }
}

Common.addNotification(j)
