import util.Plumber

def p = new Plumber(
  name: 'sqre/infra/clean-locks',
  dsl: this
)
p.pipeline().with {
  description('Cleans up leftover eups locks.')

  parameters {
    stringParam('AGENT_NUM', '', 'Agent number to run on')
    stringParam('PLATFORM_HASH', '', 'Platform hash (10 characters)')
    stringParam('ENV_HASH', '', 'Environment hash (7 characters)')
  }
}
