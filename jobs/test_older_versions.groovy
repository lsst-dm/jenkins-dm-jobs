import util.Plumber
import org.yaml.snakeyaml.Yaml

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix.yaml'))

def p = new Plumber(name: 'release/test-older-release', dsl: this)
p.pipeline().with {
  description('Builds older versions of official releases')

  parameters {
    stringParam('PRODUCTS', scipipe.canonical.products + " lsst_sitcom", 'Whitespace delimited list of EUPS products to build.')
    stringParam('VERSIONS', "30.0.0, 29.2.1", 'Versions to test')
  }
}
