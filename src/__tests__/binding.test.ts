const mockNativeModule = {
  addListener: jest.fn(),
  answerAcknowledged: jest.fn(),
  answerFailed: jest.fn(),
};

jest.mock("../ExpoCallKitModule", () => ({
  __esModule: true,
  default: mockNativeModule,
}));

// Jest must install the native-module factory before this import is evaluated.
// eslint-disable-next-line import/first
import { bindCallProviderAdapters, type CallProviderAdapter } from "../index";

const adapter: CallProviderAdapter = {
  id: "test",
  answerIncoming: async () => {},
};

describe("bindCallProviderAdapters", () => {
  beforeEach(() => {
    mockNativeModule.addListener.mockReset();
  });

  it("rolls back listeners when native registration fails partway through", () => {
    const removers = [jest.fn(), jest.fn(), jest.fn()];
    mockNativeModule.addListener.mockImplementation(() => {
      const index = mockNativeModule.addListener.mock.calls.length - 1;
      if (index === 3) throw new Error("native listener registration failed");
      return { remove: removers[index] };
    });

    expect(() => bindCallProviderAdapters([adapter])).toThrow(
      "native listener registration failed",
    );
    removers.forEach((remove) => expect(remove).toHaveBeenCalledTimes(1));
  });

  it("removes every successfully registered listener", () => {
    const removers = Array.from({ length: 8 }, () => jest.fn());
    mockNativeModule.addListener.mockImplementation(() => {
      const index = mockNativeModule.addListener.mock.calls.length - 1;
      return { remove: removers[index] };
    });

    const subscription = bindCallProviderAdapters([adapter]);
    subscription.remove();

    expect(mockNativeModule.addListener).toHaveBeenCalledTimes(8);
    removers.forEach((remove) => expect(remove).toHaveBeenCalledTimes(1));
  });
});
