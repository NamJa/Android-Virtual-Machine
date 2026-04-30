#include "binder/binder_device.h"

namespace avm::binder {

WriteCommandKind classifyWriteCommand(uint32_t code) {
    switch (code) {
        case cmd::BC_TRANSACTION:      return WriteCommandKind::TRANSACTION;
        case cmd::BC_REPLY:            return WriteCommandKind::REPLY;
        case cmd::BC_INCREFS:          return WriteCommandKind::INCREFS;
        case cmd::BC_DECREFS:          return WriteCommandKind::DECREFS;
        case cmd::BC_FREE_BUFFER:      return WriteCommandKind::FREE_BUFFER;
        case cmd::BC_DEAD_BINDER_DONE: return WriteCommandKind::DEAD_BINDER_DONE;
        default:                       return WriteCommandKind::UNKNOWN;
    }
}

ReadCommandKind classifyReadCommand(uint32_t code) {
    switch (code) {
        case cmd::BR_TRANSACTION:          return ReadCommandKind::TRANSACTION;
        case cmd::BR_REPLY:                return ReadCommandKind::REPLY;
        case cmd::BR_NOOP:                 return ReadCommandKind::NOOP;
        case cmd::BR_TRANSACTION_COMPLETE: return ReadCommandKind::TRANSACTION_COMPLETE;
        default:                           return ReadCommandKind::UNKNOWN;
    }
}

}  // namespace avm::binder
