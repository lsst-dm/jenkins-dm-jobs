import util.Plumber
import org.yaml.snakeyaml.Yaml

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix.yaml'))

def p = new Plumber(name: 'release/run-rebuild', dsl: this)
p.pipeline().with {
  description('run rebuild in the canonical LSST DM build environment.  Only us this job when preparing to publish EUPS distrib packages.')

  parameters {
    stringParam('REFS', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "main" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCTS', null, 'Whitespace delimited list of EUPS products to build.')
    booleanParam('BUILD_DOCS', false, 'Build and publish documentation.')
    booleanParam('NO_BINARY_FETCH', true, 'pass -B flag to rebuild -- builds all binary')
    booleanParam('PREP_ONLY', false, 'Pass -p flag to lsstsw/bin/rebuild -- prepare git clones/a manifest but do not build products.')
    stringParam('TIMEOUT', '8', 'build timeout in hours')
    stringParam('SPLENV_REF', scipipe.template.splenv_ref, 'conda env ref')
    // enable for debugging only
    // booleanParam('NO_VERSIONDB_PUSH', true, 'Skip push to remote versiondb repo.')
  }
}
