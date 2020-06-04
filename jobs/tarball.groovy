import util.Plumber
import org.yaml.snakeyaml.Yaml

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix.yaml'))

def p = new Plumber(name: 'release/tarball', dsl: this)
p.pipeline().with {
  description('build and publish EUPS distrib "tarball" packages')

  parameters {
    // XXX This is a kludge around lsst_ci requiring git-lfs backed products
    // stringParam('PRODUCTS', scipipe.canonical.products,
    //  'Whitespace delimited list of EUPS products to build.')
    stringParam('PRODUCTS', scipipe.tarball.products,
      'Whitespace delimited list of EUPS products to build.')
    stringParam('EUPS_TAG', null, 'published EUPS tag')
    booleanParam('SMOKE', false, 'Run a post-build installation test of generated EUPS distrib traballs.')
    booleanParam('RUN_SCONS_CHECK', false, '(no-op without SMOKE) Manually checkout the "base" product and invoke "scons".')
    booleanParam('PUBLISH', false, 'Publish generated EUPS distrib tarballs.')
    booleanParam('WIPEOUT', false, 'Completely wipe out workspace(s) before starting build.')
    stringParam('TIMEOUT', '8', 'build timeout in hours')
    stringParam('IMAGE', null, 'published EUPS tag')
    choiceParam('LABEL', ['centos-7', 'centos-6', 'osx-10.11', 'osx-10.12', 'osx-10.14'], 'Jenkins build agent label')
    stringParam('COMPILER', 'conda-system', 'compiler version string')
    choiceParam('PYTHON_VERSION', ['3'], 'Python major version')
    stringParam('MINIVER', scipipe.tarball_defaults.miniver, 'Miniconda installer version')
    stringParam('SPLENV_REF', scipipe.template.splenv_ref, 'LSST conda package set ref')
    choiceParam('OSFAMILY', ['redhat', 'osx'], 'Published osfamily name')
    stringParam('PLATFORM', null, 'Published platform name (el7, 10.9)')
  }

  concurrentBuild(true)
}
