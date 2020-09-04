import util.Plumber

def p = new Plumber(name: 'sqre/validate_drp_gen3', dsl: this)
p.pipeline().with {
  description('Execute validate_drp_gen3 and ship the results to the squash qa-dashboard.')

  parameters {
    stringParam('DOCKER_IMAGE', null, 'Explicit name of release docker image including tag.')
    booleanParam('NO_PUSH', true, 'Do not push results to squash.')
    booleanParam('WIPEOUT', false, 'Completely wipe out workspace(s) before starting build.')
  }
}
