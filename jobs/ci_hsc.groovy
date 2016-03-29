def name = 'ci_hsc'
def org = 'jhoblitt'
def slug = "${org}/${name}"

job("${name}-test") {
  scm {
    git {
      remote {
        github(slug)
        //refspec('+refs/pull/*:refs/remotes/origin/pr/*')
      }
      branch('*/master')
      extensions {
        cloneOptions {
          shallow(true)
        }
      }
    }
  }

  parameters {
    stringParam('BRANCH', null, "Whitespace delimited list of 'refs' to attempt to build.  Priority is highest -> lowest from left to right.  'master' is implicitly appended to the right side of the list, if not specified.")
  }

  properties {
    rebuild {
      autoRebuild()
    }
  }
  concurrentBuild()
  label('jenkins-master')
  keepDependencies()

  triggers {
    cron('H 0,8,16 * * *')
    githubPush()
    pullRequest {
      cron('H/5 * * * *')
      useGitHubHooks()
      permitAll()
      // configure credential to use for GH API
      configure { project ->
        project / 'triggers' / 'org.jenkinsci.plugins.ghprb.GhprbTrigger' {
          gitHubAuthId 'github-api-token-jhoblitt'
        }
      }
    }
  }

  wrappers {
    // 'The ghprbSourceBranch' variable is defined when the build was triggered
    // by a GH PR.  If set, extract it for later use as the 'BRANCH' parameter
    // to the 'stack-os-matrix'.  Otherwise, pass on the 'BRANCH' parameter
    // this build was invoked with (it may be blank).
    configure { project ->
      project / 'buildWrappers' / 'EnvInjectBuildWrapper' / 'info' {
        // yes, groovy heredocs are this lame...
        groovyScriptContent """
if (binding.variables.containsKey('ghprbSourceBranch')) {
  return [BUILD_BRANCH: ghprbSourceBranch]
} else {
  return [BUILD_BRANCH: BRANCH]
}
"""
      }
    }
  }

  steps {
    downstreamParameterized {
      trigger('stack-os-matrix') {
        block {
          buildStepFailure('FAILURE')
          failure('FAILURE')
        }
        parameters {
          //currentBuild()
          predefinedProps([
            PRODUCT: name,
            BRANCH: '$BUILD_BRANCH'
          ])
          booleanParam('SKIP_DEMO', true)
        }
      }
    }
  }

  publishers {
    // must be defined even to use the global defaults
    hipChat {}
  }
}
