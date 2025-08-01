def p = new Plumber(name: 'sqre/infra/cache_lsstsw', dsl: this)
p.pipeline().with {
  description('Update lsstsw and stores it in gcp bucket')

  parameters {
    stringParam('ARCHITECTURE', 'linux-64', 'Architecture to run script.')
    booleanParam('NO_PUSH', false, 'Do not push to gcp')
    stringParam('DATE_TAG', 'w_latest', 'What eups tag should we use to bachup lsstsw')
    stringParam('SPLENV_REF', scipipe.template.splenv_ref, 'conda env ref')
  }
}
