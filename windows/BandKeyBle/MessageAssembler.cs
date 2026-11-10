using System.Text;

namespace BandKeyBle;

public sealed class MessageAssembler
{
    private readonly Dictionary<string, List<byte>> _buffers = new();
    private readonly object _gate = new();
    private readonly int _maxBytes;

    public MessageAssembler(int maxBytes = BleConstants.MaxMessageBytes)
    {
        _maxBytes = maxBytes;
    }

    public IReadOnlyList<string> Append(string key, ReadOnlySpan<byte> chunk)
    {
        lock (_gate)
        {
            if (!_buffers.TryGetValue(key, out var buffer))
            {
                buffer = new List<byte>();
                _buffers[key] = buffer;
            }

            foreach (var value in chunk)
            {
                buffer.Add(value);
            }

            var messages = new List<string>();
            var start = 0;
            for (var i = 0; i < buffer.Count; i++)
            {
                if (buffer[i] != BleConstants.FrameTerminator)
                {
                    continue;
                }

                if (i > start)
                {
                    messages.Add(Encoding.UTF8.GetString(buffer.GetRange(start, i - start).ToArray()));
                }

                start = i + 1;
            }

            if (start > 0)
            {
                buffer.RemoveRange(0, start);
            }

            if (buffer.Count > _maxBytes)
            {
                buffer.Clear();
            }

            return messages;
        }
    }

    public void Reset(string key)
    {
        lock (_gate)
        {
            _buffers.Remove(key);
        }
    }
}
