import util.Plumber
import org.yaml.snakeyaml.Yaml

def sqre = new Yaml().load(readFileFromWorkspace('etc/sqre/config.yaml'))

def p = new Plumber(name: 'sqre/infrastructure/build-codekit', dsl: this)
p.pipeline().with {
  description('Constructs docker sqre-codekit images.')

  parameters {
    stringParam('CODEKIT_VER', sqre.codekit.pypi.version, 'sqre-codekit pypi version to install')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', false, 'Also push to docker registry with "latest" tag.')
  }
}
