# Contributing to linkerd

:balloon: Thanks for your help improving the project!

## Getting Help

If you have a question about linkerd or have encountered problems using it, you may [file
an issue][issue] or join us on [Slack][slack].

## Submitting a Pull Request

Do you have an improvement?

1. Submit an [issue][issue] describing your proposed change.
2. We will try to respond to your issue promptly.
3. If your proposed change is accepted, and you haven't already done so, sign our [Contributor License Agreement][cla].
4. Fork this repo, develop and test your code changes. See the project's [README](README.md) for further information about working in this repository.
5. Submit a pull request against this repo's `master` branch.
6. Your branch may be merged once all configured checks pass, including:
    - 2 code review approvals, at least 1 of which is from a buoyant member
    - The branch has passed tests in CI.

## Code Style

We generally follow [Effective Scala][es] and the
[Scala Style Guide][ssg]. When in doubt, we try to use Finagle's
idioms. 

## Committing

We prefer squash or rebase commits so that all changes from a branch are committed to
master a single commit.

### Commit messages

Finalized commit messages should be in the following format:

```
Subject

Problem

Solution

Validation
```

#### Subject

- one line, <= 50 characters
- describe what is done; not the result
- use the active voice
- capitalize properly
- do not end in a period — this is a title/subject
- reference the pull request by number

##### Examples

bad: server disconnects should cause dst client disconnects.
good: Propagate disconnects from source to destination

bad: support tls servers
good: Introduce support for server-side TLS (#347)

#### Problem

Explain the context and why you're making that change.  What is the
problem you're trying to solve? In some cases there is not a problem
and this can be thought of being the motivation for your change.

#### Solution

Describe the modifications you've made.

#### Validation

Describe the testing you've done to validate your change.  Performance-related changes
should include before- and after- benchmark results.

[cla]: https://buoyant.io/cla/
[es]: https://twitter.github.io/effectivescala/
[issue]: https://github.com/linkerd/linkerd/issues/new
[slack]: http://slack.linkerd.io/
[ssg]: http://docs.scala-lang.org/style/scaladoc.html
