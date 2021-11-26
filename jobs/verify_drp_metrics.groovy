import util.Plumber

def p = new Plumber(name: 'sqre/verify_drp_metrics', dsl: this)
p.pipeline().with {
  description('Execute verify_drp_metrics and ship the results to SQuaSH.')

  parameters {
    stringParam('DOCKER_IMAGE', null, 'Explicit name of release docker image including tag.')
    booleanParam('NO_PUSH', true, 'Do not push results to squash.')
    booleanParam('WIPEOUT', false, 'Completely wipe out workspace(s) before starting build.')
    stringParam('GIT_REF', 'main', 'Git ref (e.g. tickets/DM-NNNNN) of faro to use.')
  }
}
