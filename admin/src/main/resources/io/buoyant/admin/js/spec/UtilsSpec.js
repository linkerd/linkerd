"use strict";

define(['src/utils'], function(Utils) {
  describe("Utils", function() {
    it("calculates a simple success rate", function() {
      var half = new Utils.SuccessRate(10,10);

      expect(half.get()).toBe(0.5);
    });
  });
});
