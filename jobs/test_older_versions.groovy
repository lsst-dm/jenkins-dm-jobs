import util.Plumber
import org.yaml.snakeyaml.Yaml

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix.yaml'))

def p = new Plumber(name: 'release/test-older-release', dsl: this)
p.pipeline().with {
  description('Builds older versions of official releases')

  parameters {
    stringParam('PRODUCTS', scipipe.canonical.products + " lsst_sitcom", 'Whitespace delimited list of EUPS products to build.')
    stringParam('VERSIONS', "o_latest, v29_2_1", 'Versions to test')
  }
}
