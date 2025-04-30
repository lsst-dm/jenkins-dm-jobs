import util.Plumber

def p = new Plumber(name: 'scipipe/ap_verify', dsl: this)
p.pipeline().with {
  description('Execute ap_verify.')

  parameters {
    stringParam('REF', null, 'Git "ref" of ap_verify to attempt to build.  Default uses version in release docker image.')
    stringParam('DOCKER_IMAGE', 'ghcr.io/lsst/scipipe', 'Explicit name of release docker image including tag.')
    stringParam('ARCHITECTURE', 'linux-64', 'Architecture to run against.')
    stringParam('DATASET_REF', null, 'Override git ref used for dataset repos.  Default uses ref from ap_verify.yaml config file.')
    booleanParam('NO_PUSH', true, 'Do not push results to squash.')
    booleanParam('WIPEOUT', false, 'Completely wipe out workspace(s) before starting build.')
  }
}
