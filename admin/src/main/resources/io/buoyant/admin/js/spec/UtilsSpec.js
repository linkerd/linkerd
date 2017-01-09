"use strict";

define(['src/utils'], function(Utils) {
  describe("Utils", function() {

    describe("SuccessRate", function() {
      it("calculates a simple success rate", function() {
        var half = new Utils.SuccessRate(10,10);

        expect(half.get()).toBe(0.5);
        expect(half.prettyRate()).toBe("50.00%");
        expect(half.rateStyle()).toBe("sr-bad");
      });

      it("doesn't divide by zero", function() {
        var na = new Utils.SuccessRate(0, 0);

        expect(na.get()).toBe(-1);
        expect(na.prettyRate()).toBe("N/A");
        expect(na.rateStyle()).toBe("sr-undefined");
      });

      it("displays a rounded percentage to 2 d.p.", function() {
        var srBad = new Utils.SuccessRate(17, 2);
        var srPoor = new Utils.SuccessRate(87,2);
        var srGood = new Utils.SuccessRate(9990, 5);

        expect(srBad.get()).toBe(17/19);
        expect(srBad.prettyRate()).toBe("89.47%");
        expect(srBad.rateStyle()).toBe("sr-bad");

        expect(srPoor.get()).toBe(87/89);
        expect(srPoor.prettyRate()).toBe("97.75%");
        expect(srPoor.rateStyle()).toBe("sr-poor");

        expect(srGood.get()).toBe(9990/9995);
        expect(srGood.prettyRate()).toBe("99.95%");
        expect(srGood.rateStyle()).toBe("sr-good");
      });
    });

    describe("MsToStringConverter", function() {
      var msToStr = new Utils.MsToStringConverter();

      var secondInMs = 1000;
      var minuteInMs = 60 * secondInMs;
      var hourInMs = 60 * minuteInMs;
      var dayInMs = 24 * hourInMs;
      var yearInMs = 365.242 * dayInMs;

      it("displays simple ms (sub-second) with units", function() {
        expect(msToStr.convert(123)).toBe("123ms");
        expect(msToStr.convert(999)).toBe("999ms");
      });

      it("converts large ms to the appropriate number of smaller time units", function() {
        expect(msToStr.convert(50 * hourInMs)).toBe("2d 2h 0m");
        expect(msToStr.convert(20 * dayInMs + 3 * hourInMs + 1 * minuteInMs)).toBe("20d 3h 1m");
        expect(msToStr.convert(20 * dayInMs + 25 * hourInMs + 1 * minuteInMs)).toBe("21d 1h 1m");
      });

      it("does not contain the twitter-server bug at year fractions bigger than 0.5", function() {
        // test for twitter-server bug
        /*
          Just gonna document this here.

          In twitter-server's MsToStringConverter:

          example: 183 days i.e. 15811200000 ms
          >> msToStr.convert(15811200000) // 183 days
          >> "-183d 18h 11m"

          looking at MsToStringConverter.prototype.convert,
          var years = (ms/this.msInYear).toFixed(0) // years = 1

          var days = Math.floor(((ms - this.msInYear*years)/this.msInDay))

          i.e.  15811200000 - (365 * dayInMs) = -15724800000
                -15724800000 / 86400000 = -182


          5 * 365 * dayInMs
          157680000000

          msToStr.convert(157680000000)
          "5y -2d 18h"
        */
        expect(msToStr.convert(181 * dayInMs)).toBe("181d 0h 0m");
        expect(msToStr.convert(182 * dayInMs)).toBe("182d 0h 0m");
        expect(msToStr.convert(183 * dayInMs)).toBe("183d 0h 0m");
        expect(msToStr.convert(184 * dayInMs)).toBe("184d 0h 0m");
      });

      it("handles year boundaries correctly", function() {
        // test for twitter-server bug
        expect(msToStr.convert(365 * dayInMs)).toBe("365d 0h 0m");
        expect(msToStr.convert(366 * dayInMs)).toBe("1y 0d 18h"); // because of the 0.242
        expect(msToStr.convert(5 * 365 * dayInMs)).toBe("4y 364d 0h");
        expect(msToStr.convert(9 * yearInMs)).toBe("9y 0d 0h");
        expect(msToStr.convert(18 * yearInMs)).toBe("18y 0d 0h");
      });
    });

    describe("BytesToStringConverter", function() {
      var bytesToStr = new Utils.BytesToStringConverter();

      it("displays bytes with units", function() {
        expect(bytesToStr.convert(999)).toBe("999B");
        expect(bytesToStr.convert(1023)).toBe("1023B");
      });

      it("converts bytes to kilobytes, rounds, displays with units", function() {
        expect(bytesToStr.convert(1024)).toBe("1.0KB");
        expect(bytesToStr.convert(1025)).toBe("1.0KB");
        expect(bytesToStr.convert(1991)).toBe("1.9KB");
        expect(bytesToStr.convert(1999)).toBe("2.0KB");
        expect(bytesToStr.convert(8 * 1024)).toBe("8.0KB");
      });

      it("converts bytes to megabytes, rounds, displays with units", function() {
        expect(bytesToStr.convert(1023 * 1024)).toBe("1023.0KB");
        expect(bytesToStr.convert(1024 * 1024)).toBe("1.0MB");
        expect(bytesToStr.convert(1025 * 1024)).toBe("1.0MB");
      });

      it("converts bytes to gigabytes, rounds, displays with units", function() {
        expect(bytesToStr.convert(1023 * 1024 * 1024)).toBe("1023.0MB");
        expect(bytesToStr.convert(1024 * 1024 * 1024 )).toBe("1.0GB");
        expect(bytesToStr.convert(1025 * 1024 * 1024)).toBe("1.0GB");
        expect(bytesToStr.convert(1025 * 1024 * 1024)).toBe("1.0GB");

        expect(bytesToStr.convert(5 * 1024 * 1024 * 1024)).toBe("5.0GB");
        expect(bytesToStr.convert(5.5 * 1024 * 1024 * 1024)).toBe("5.5GB");
        expect(bytesToStr.convert(5.25 * 1024 * 1024 * 1024)).toBe("5.3GB");
      });
    });
  });
});
