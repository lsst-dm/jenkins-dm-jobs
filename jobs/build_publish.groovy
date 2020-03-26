import util.Plumber
import org.yaml.snakeyaml.Yaml

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix.yaml'))

def p = new Plumber(name: 'release/build-publish', dsl: this)
p.pipeline().with {
  description('Run release/run-rebuild & release/run-publish in series')

  parameters {
    stringParam('REFS', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCTS', null, 'Whitespace delimited list of EUPS products to build.')
    stringParam('EUPS_TAG', null, 'EUPS tag string. Note that EUPS does not deal well with "." or "-" as part of a tag')
    booleanParam('BUILD_DOCS', true, 'Build and publish documentation.')
    stringParam('SPLENV_REF', scipipe.template.splenv_ref, 'conda env ref')
  }
}
