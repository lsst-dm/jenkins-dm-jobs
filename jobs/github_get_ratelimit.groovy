import util.Plumber

def p = new Plumber(name: 'release/codekit/github-get-ratelimit', dsl: this)
p.pipeline().with {
  def text = '''
    Display the current github ReST API request ratelimit.

    Example:

      $ github-get-ratelimit
      INFO:codekit:github ratelimit: (4353, 5000)
      INFO:codekit:github ratelimit reset: 2018-06-07 06:24:16
  '''
  description(text.replaceFirst("\n","").stripIndent())
}
