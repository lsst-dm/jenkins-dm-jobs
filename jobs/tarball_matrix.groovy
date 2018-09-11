import util.Plumber
import org.yaml.snakeyaml.Yaml

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix.yaml'))

def p = new Plumber(name: 'release/tarball-matrix', dsl: this)
p.pipeline().with {
  description('build and publish EUPS distrib "tarball" packages')

  parameters {
    stringParam('PRODUCTS', scipipe.canonical.products,
      'Whitespace delimited list of EUPS products to build.')
    stringParam('EUPS_TAG', null, 'published EUPS tag')
    booleanParam('SMOKE', true, 'Run a post-build installation test of generated EUPS distrib traballs.')
    booleanParam('RUN_SCONS_CHECK', true, '(no-op without SMOKE) Manually checkout the "base" product and invoke "scons".')
    booleanParam('PUBLISH', false, 'Publish generated EUPS distrib tarballs.')
  }

  concurrentBuild(true)
}
