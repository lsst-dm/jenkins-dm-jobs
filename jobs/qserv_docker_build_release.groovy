import util.Plumber

def p = new Plumber(name: 'qserv/docker/build-release', dsl: this)
p.pipeline()
