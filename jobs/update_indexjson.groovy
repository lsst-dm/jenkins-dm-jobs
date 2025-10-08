import util.Plumber

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix.yaml'))

def p = new Plumber(name: 'sqre/infra/update_indexjson', dsl: this)
p.pipeline().with {
  description('Update index.json file for gcp bucket')

  parameters {
    stringParam('ARCHITECTURE', 'linux-64', 'Architecture to run script.')
    stringParam('SPLENV_REF', scipipe.template.splenv_ref, "Rubin env to update"),
    stringParam('MINI_VER', scipipe.template.tarball_defaults.miniver, "Minconda version"),
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
  }
}
