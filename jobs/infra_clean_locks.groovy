job('sqre/infra/clean_locks') {
  description('Clean up leftover eups lockfiles')
  
  parameters {
    stringParam('AGENT_NUM', '', 'Agent number to run on')
    stringParam('PLATFORM_HASH', '', 'Platform hash (10 characters)')
    stringParam('ENV_HASH', '', 'Environment hash (7 characters)')
  }

  steps {
    node('agent-ldfc-${params.AGENT_NUM}') {
      dir('/j/ws/stack-os-matrix/${params.PLATFORM_HASH}/lsstsw/stack/${params.ENV_HASH}/.lockDir') {
         deleteDir()
      }
    }
  }
}
