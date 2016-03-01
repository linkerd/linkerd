# Contributing to linkerd #

:balloon: Thank you for using the linkerd project!

## Getting Help ##

If you have a question about linkerd or have encountered problems
using linkerd, you may [file an issue][issue] or join us on
[Slack][slack].

## Submitting Changes ##

If you'd like to make a change to linkerd, you should do the
following:

1. Submit an [issue][issue] describing your proposed change.
2. We will respond to your issue promptly.
3. If your proposed change is accepted, and you haven't already done
so, sign [Buoyant's Contributor License Agreement][cla].  Before we
can accept your patches, our lawyers would like to be assured that:
    - The code you're contributing is yours, and you have the right to
    license it.
    - You're granting us a license to distribute said code under the
    terms of this agreement.
4. Fork the linkerd repo, develop and test your code
changes. See the project's [README](README.md) for further information
about working in this repository.
5. Submit a pull request against linkerd's `master` branch.
6. Your branch may be merged once all configured checks pass,
including:
    - 2 shipits via [ReviewNinja](https://review.ninja).  A shipit is
    typically indicated by placing a :star: or :+1: in the review.
    - The branch has passed tests in CI.

### Git history ###

Prior to submitting a pull request, please revise the commit history
of your branch such that each commit is self-explanatory, even without
the context of the pull request, and that it compiles & tests
cleanly.

Typically, a pull request consists of a single commit.  It may
occasionally be preferable to submit a change with several commits,
but each should be a complete change needed to complete a larger
feature.  Each commit should be documented appropriately.

Thank you for getting involved!
:heart: Team Buoyant

[cla]: https://buoyant.io/cla/
[issue]: https://github.com/buoyantio/linkerd/issues/new
[slack]: http://slack.linkerd.io/
